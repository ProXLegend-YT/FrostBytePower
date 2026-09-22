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

    /**
     * Forces the display on via a real overlay window rather than a raw
     * wake lock. On some Samsung/One UI builds (confirmed on Galaxy A75),
     * PowerManager.FULL_WAKE_LOCK acquired from an accessibility-service
     * context is silently ignored - no crash, no error, it just doesn't
     * turn the screen on. A WindowManager overlay carrying
     * FLAG_TURN_SCREEN_ON + FLAG_DISMISS_KEYGUARD is a much stronger,
     * window-level signal that One UI actually honors, since it's the
     * same mechanism used by legitimate full-screen incoming-call UIs.
     * The window is fully transparent and removes itself shortly after,
     * so nothing is visibly drawn - it exists only to carry those flags.
     */
    private fun wakeScreenViaOverlay() {
        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val overlayView = android.view.View(this)

            val params = android.view.WindowManager.LayoutParams(
                1, 1,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.TRANSLUCENT
            )

            windowManager.addView(overlayView, params)

            // Also acquire the wake lock as a belt-and-braces measure for
            // devices where it does work - harmless if it's a no-op here.
            DeviceUtils.wakeScreen(this)

            handler.postDelayed({
                try { windowManager.removeView(overlayView) } catch (e: Exception) { /* already gone */ }
            }, 1500L)
        } catch (e: Exception) {
            // Fall back to the wake lock alone if the overlay can't be
            // added for any reason (e.g. permission revoked).
            DeviceUtils.wakeScreen(this)
        }
    }

    private fun handleShake() {
        // Only fires when the screen is off - shaking while already
        // unlocked should do nothing.
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isInteractive) return

        wakeScreenViaOverlay()
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
                wakeScreenViaOverlay()
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

                // Force speaker audio right now too, not just for future
                // calls - covers the case where the sensor is already
                // stuck and the screen is black *right now* (mid-call, or
                // mid voice-message playback, which the automatic
                // call-state listener can't see).
                DeviceUtils.forceSpeakerphoneNow(this)
                wakeScreenViaOverlay()

                vibrateFeedback()
                android.widget.Toast.makeText(
                    this,
                    if (newState) "Speaker ON — proximity fix enabled"
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
            ButtonAction.TAP_SENSORS_OFF_TILE -> {
                val pos = PowerPrefs.getSensorsOffTapPosition(this)
                if (pos != null) {
                    DeviceUtils.openQuickSettingsAndTap(this, pos.first, pos.second)
                } else {
                    // Not calibrated yet - fall back to just opening the
                    // shade so the button still does something useful,
                    // and tell the user why it didn't auto-tap.
                    DeviceUtils.openQuickSettings(this)
                    android.widget.Toast.makeText(
                        this,
                        "Not calibrated yet — set this up in app settings first",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                vibrateFeedback()
                true
            }
            ButtonAction.DISABLED -> true
        }
    }

    /**
     * Draws a real full-screen transparent overlay window (TYPE_ACCESSIBILITY_OVERLAY)
     * on top of everything, including the system quick-settings shade, opens the shade,
     * and records the absolute screen coordinates of the user's next tap. This has to
     * live here rather than in MainActivity's Compose tree, because a normal app window
     * can never draw above System UI's shade - only an accessibility-service overlay can.
     * Calls [onCaptured] with the tapped (x, y) once received, or nothing if cancelled.
     */
    fun startSensorsOffCalibration(onCaptured: (Int, Int) -> Unit, onCancelled: () -> Unit) {
        val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val overlayView = android.widget.FrameLayout(this)

        val instructionText = android.widget.TextView(this).apply {
            text = "Tap the Sensors Off tile in the shade above"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#CC000000"))
            textSize = 14f
            setPadding(32, 24, 32, 24)
        }
        overlayView.addView(
            instructionText,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL; bottomMargin = 60 }
        )

        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        var removed = false
        fun removeOverlay() {
            if (removed) return
            removed = true
            try { windowManager.removeView(overlayView) } catch (e: Exception) { /* already gone */ }
        }

        overlayView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                removeOverlay()
                onCaptured(x, y)
                true
            } else false
        }

        try {
            windowManager.addView(overlayView, params)
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        } catch (e: Exception) {
            removeOverlay()
            onCancelled()
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
