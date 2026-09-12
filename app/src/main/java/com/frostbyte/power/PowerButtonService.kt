package com.frostbyte.power

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class PowerButtonService : AccessibilityService() {

    private var shakeDetector: ShakeDetector? = null
    private var proximityOverride: ProximityOverride? = null
    private val handler = Handler(Looper.getMainLooper())

    // Double-tap / long-press tracking, per key.
    private var lastVolUpTime = 0L
    private var lastVolDownTime = 0L
    private var volUpLongPressFired = false
    private var volDownLongPressFired = false
    private val volUpLongPressRunnable = Runnable {
        volUpLongPressFired = true
        runAction(PowerPrefs.getVolumeUpLongPressAction(this))
    }
    private val volDownLongPressRunnable = Runnable {
        volDownLongPressFired = true
        runAction(PowerPrefs.getVolumeDownLongPressAction(this))
    }

    private val doubleTapWindowMs = 300L
    private val longPressWindowMs = 500L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        shakeDetector = ShakeDetector(this) { handleShake() }
        proximityOverride = ProximityOverride(this)
        applyAllLiveSettings()
    }

    // Called from MainActivity whenever any live-affecting setting changes,
    // so the running service picks it up without a restart.
    fun applyAllLiveSettings() {
        val sensitivity = PowerPrefs.getShakeSensitivity(this)
        val lowPower = PowerPrefs.isLowPowerMode(this)
        val batteryCutoffEnabled = PowerPrefs.isShakeBatteryCutoffEnabled(this)
        val batteryCutoff = PowerPrefs.getShakeBatteryCutoffPercent(this)
        val batteryLow = batteryCutoffEnabled && DeviceUtils.getBatteryPercent(this) <= batteryCutoff

        shakeDetector?.updateLowPowerMode(lowPower)
        shakeDetector?.updateSensitivity(sensitivity)
        if (sensitivity == ShakeSensitivity.DISABLED || batteryLow) {
            shakeDetector?.stop()
        } else {
            shakeDetector?.start()
        }

        proximityOverride?.refresh()
    }

    private fun handleShake() {
        // Only fires when the screen is off - shaking while already
        // unlocked should do nothing.
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isInteractive) return

        DeviceUtils.wakeScreen(this)
        vibrateFeedback()
    }

    private fun vibrateFeedback() {
        if (!PowerPrefs.isVibrationFeedbackEnabled(this)) return
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(40)
        }
    }

    private fun runAction(action: ButtonAction): Boolean {
        return when (action) {
            ButtonAction.DEFAULT -> false
            ButtonAction.TURN_OFF_SCREEN -> {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                vibrateFeedback()
                true
            }
            ButtonAction.WAKE_SCREEN -> {
                DeviceUtils.wakeScreen(this)
                vibrateFeedback()
                true
            }
            ButtonAction.OPEN_POWER_MENU -> {
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                vibrateFeedback()
                true
            }
            ButtonAction.TOGGLE_FLASHLIGHT -> {
                DeviceUtils.toggleFlashlight(this)
                vibrateFeedback()
                true
            }
            ButtonAction.TAKE_SCREENSHOT -> {
                DeviceUtils.takeScreenshot(this)
                vibrateFeedback()
                true
            }
            ButtonAction.TOGGLE_PROXIMITY_OVERRIDE -> {
                val newState = !PowerPrefs.isIgnoreProximityEnabled(this)
                PowerPrefs.setIgnoreProximityEnabled(this, newState)
                proximityOverride?.refresh()
                vibrateFeedback()
                android.widget.Toast.makeText(
                    this,
                    if (newState) "Proximity fix ON — calls will force speaker"
                    else "Proximity fix OFF",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                true
            }
            ButtonAction.OPEN_QUICK_SETTINGS -> {
                DeviceUtils.openQuickSettings(this)
                vibrateFeedback()
                true
            }
            ButtonAction.DISABLED -> true
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount != 0) return false

        val isVolUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isVolDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        val isPower = event.keyCode == KeyEvent.KEYCODE_POWER

        if (!isVolUp && !isVolDown && !isPower) return false

        if (isPower) {
            if (event.action != KeyEvent.ACTION_DOWN) return false
            return runAction(PowerPrefs.getPowerButtonAction(this))
        }

        // Volume keys get single/double-tap/long-press handling.
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (isVolUp) {
                    volUpLongPressFired = false
                    handler.postDelayed(volUpLongPressRunnable, longPressWindowMs)
                } else {
                    volDownLongPressFired = false
                    handler.postDelayed(volDownLongPressRunnable, longPressWindowMs)
                }
                // Consume the key only if any binding beyond DEFAULT exists,
                // so a fully-default key still behaves like stock volume.
                hasAnyBinding(isVolUp)
            }
            KeyEvent.ACTION_UP -> {
                if (isVolUp) {
                    handler.removeCallbacks(volUpLongPressRunnable)
                    if (volUpLongPressFired) return hasAnyBinding(true)
                } else {
                    handler.removeCallbacks(volDownLongPressRunnable)
                    if (volDownLongPressFired) return hasAnyBinding(false)
                }

                val now = System.currentTimeMillis()
                val lastTime = if (isVolUp) lastVolUpTime else lastVolDownTime
                val isDoubleTap = now - lastTime < doubleTapWindowMs

                if (isDoubleTap) {
                    val doubleAction = if (isVolUp)
                        PowerPrefs.getVolumeUpDoubleTapAction(this)
                    else
                        PowerPrefs.getVolumeDownDoubleTapAction(this)
                    if (isVolUp) lastVolUpTime = 0L else lastVolDownTime = 0L
                    runAction(doubleAction)
                } else {
                    if (isVolUp) lastVolUpTime = now else lastVolDownTime = now
                    // Delay the single-tap action slightly so a following
                    // second tap can still be caught as a double-tap.
                    handler.postDelayed({
                        val elapsed = System.currentTimeMillis() - now
                        if (elapsed >= doubleTapWindowMs) {
                            val singleAction = if (isVolUp)
                                PowerPrefs.getVolumeUpAction(this)
                            else
                                PowerPrefs.getVolumeDownAction(this)
                            runAction(singleAction)
                        }
                    }, doubleTapWindowMs)
                }
                hasAnyBinding(isVolUp)
            }
            else -> false
        }
    }

    private fun hasAnyBinding(isVolUp: Boolean): Boolean {
        val single = if (isVolUp) PowerPrefs.getVolumeUpAction(this) else PowerPrefs.getVolumeDownAction(this)
        val double = if (isVolUp) PowerPrefs.getVolumeUpDoubleTapAction(this) else PowerPrefs.getVolumeDownDoubleTapAction(this)
        val long = if (isVolUp) PowerPrefs.getVolumeUpLongPressAction(this) else PowerPrefs.getVolumeDownLongPressAction(this)
        return single != ButtonAction.DEFAULT || double != ButtonAction.DEFAULT || long != ButtonAction.DEFAULT
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for key interception; required override.
    }

    override fun onInterrupt() {
        shakeDetector?.stop()
        proximityOverride?.teardown()
    }

    override fun onDestroy() {
        super.onDestroy()
        shakeDetector?.stop()
        proximityOverride?.teardown()
        handler.removeCallbacksAndMessages(null)
        instance = null
    }

    companion object {
        var instance: PowerButtonService? = null
    }
}
