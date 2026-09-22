package com.frostbyte.power

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
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
    private var pendingVolUpSingleTap: Runnable? = null
    private var pendingVolDownSingleTap: Runnable? = null
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
     * Forces the display (and lock screen, if locked) back on.
     *
     * History: a raw PowerManager.FULL_WAKE_LOCK from this service context
     * was confirmed not to work on the user's device. A WindowManager
     * overlay carrying FLAG_TURN_SCREEN_ON was tried next, but that also
     * failed - confirmed the device runs Android 10, where those flags
     * are deprecated (since 8.1) and no longer honored outside a real
     * Activity. The only mechanism Android 10 actually supports from a
     * background service is starting a transparent Activity that calls
     * the modern setTurnScreenOn()/setShowWhenLocked() APIs on itself, so
     * that's what this does now - see WakeScreenActivity.
     */
    private fun wakeScreenViaOverlay() {
        try {
            val intent = Intent(this, WakeScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fall back to the wake lock alone if the activity can't be
            // started for any reason.
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
                // Consume the key on ACTION_DOWN whenever ANY gesture on
                // this key is bound (single, double, or long-press) - not
                // just long-press.
                //
                // Bug history: this used to only check long-press here,
                // reasoning that single/double resolve later on ACTION_UP
                // so only long-press could matter at ACTION_DOWN time.
                // That reasoning missed a real consequence: if ACTION_DOWN
                // is NOT consumed, Android's own default volume handling
                // runs immediately and independently of this app - it
                // doesn't wait to see what ACTION_UP produces. So with
                // only single-tap bound (no long-press), every press would
                // both change the system volume AND separately schedule
                // this app's single-tap action, and depending on the
                // specific device/OS build the two could interfere with
                // each other or make the assigned action seem like it
                // "isn't working." Consuming on DOWN whenever any gesture
                // is bound prevents system volume handling from ever
                // running in parallel with the app's own gesture logic.
                hasAnyBinding(isVolUp)
            }
            KeyEvent.ACTION_UP -> {
                if (isVolUp) {
                    handler.removeCallbacks(volUpLongPressRunnable)
                    if (volUpLongPressFired) return isGestureBound(true, Gesture.LONG)
                } else {
                    handler.removeCallbacks(volDownLongPressRunnable)
                    if (volDownLongPressFired) return isGestureBound(false, Gesture.LONG)
                }

                val now = System.currentTimeMillis()
                val lastTime = if (isVolUp) lastVolUpTime else lastVolDownTime
                val isDoubleTap = now - lastTime < doubleTapWindowMs

                if (isDoubleTap) {
                    // A genuine double-tap: cancel the pending single-tap
                    // action from the first press so it doesn't also fire.
                    if (isVolUp) {
                        pendingVolUpSingleTap?.let { handler.removeCallbacks(it) }
                        pendingVolUpSingleTap = null
                    } else {
                        pendingVolDownSingleTap?.let { handler.removeCallbacks(it) }
                        pendingVolDownSingleTap = null
                    }
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
                    //
                    // Bug history: this used to re-check
                    // (System.currentTimeMillis() - now >= doubleTapWindowMs)
                    // inside the delayed callback itself. That comparison is
                    // measuring against the exact same delay it was
                    // scheduled with, so ordinary Handler/GC jitter of even
                    // a few ms made "elapsed" land just under the window
                    // far more often than not - silently dropping the
                    // single-tap action almost every time. The fact that
                    // this callback runs at all (i.e. wasn't cancelled
                    // above by a real double-tap) is already sufficient
                    // proof no double-tap happened, so it now just runs
                    // unconditionally.
                    val singleTapRunnable = Runnable {
                        val singleAction = if (isVolUp)
                            PowerPrefs.getVolumeUpAction(this)
                        else
                            PowerPrefs.getVolumeDownAction(this)
                        runAction(singleAction)
                        if (isVolUp) pendingVolUpSingleTap = null else pendingVolDownSingleTap = null
                    }
                    if (isVolUp) pendingVolUpSingleTap = singleTapRunnable else pendingVolDownSingleTap = singleTapRunnable
                    handler.postDelayed(singleTapRunnable, doubleTapWindowMs)
                }
                isGestureBound(isVolUp, if (isDoubleTap) Gesture.DOUBLE else Gesture.SINGLE)
            }
            else -> false
        }
    }

    /**
     * Whether the OS's default volume behavior should be suppressed for
     * this specific gesture (single tap / double tap / long press) - NOT
     * whether any of the three gestures on this key has a binding.
     *
     * Bug history: this used to return true if ANY of single/double/long
     * had a non-default action, which meant binding just the long-press
     * (e.g. the volume-down proximity/speaker fix) silently ate every
     * single plain tap too, breaking normal volume control entirely.
     * Each gesture must only consume the key if THAT gesture is bound.
     */
    private fun isGestureBound(isVolUp: Boolean, gesture: Gesture): Boolean {
        val action = when (gesture) {
            Gesture.SINGLE -> if (isVolUp) PowerPrefs.getVolumeUpAction(this) else PowerPrefs.getVolumeDownAction(this)
            Gesture.DOUBLE -> if (isVolUp) PowerPrefs.getVolumeUpDoubleTapAction(this) else PowerPrefs.getVolumeDownDoubleTapAction(this)
            Gesture.LONG -> if (isVolUp) PowerPrefs.getVolumeUpLongPressAction(this) else PowerPrefs.getVolumeDownLongPressAction(this)
        }
        return action != ButtonAction.DEFAULT
    }

    private enum class Gesture { SINGLE, DOUBLE, LONG }

    private fun hasAnyBinding(isVolUp: Boolean): Boolean {
        return isGestureBound(isVolUp, Gesture.SINGLE) ||
            isGestureBound(isVolUp, Gesture.DOUBLE) ||
            isGestureBound(isVolUp, Gesture.LONG)
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
