package com.frostbyte.power

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings

object DeviceUtils {

    private var flashlightOn = false
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Manual "force speaker now" for the broken-proximity-sensor workaround.
     * Unlike [ProximityOverride], which only fires automatically when an
     * actual phone call connects, this can be triggered at any moment -
     * including while a voice message is playing back (e.g. in WhatsApp),
     * which routes its own audio and never touches call state, so the
     * automatic listener can't see it.
     *
     * Deliberately does NOT set AudioManager.MODE_IN_CALL here (that's only
     * valid/safe during an actual telephony call and would disrupt normal
     * media playback); just flips the speakerphone routing flag, which is
     * enough to pull audio off the earpiece and stop the sensor from
     * blacking out the screen.
     */
    fun forceSpeakerphoneNow(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.isSpeakerphoneOn = true
        } catch (e: Exception) {
            // Fail silently - button press shouldn't crash the service.
        }
    }

    fun toggleFlashlight(context: Context) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            flashlightOn = !flashlightOn
            cameraManager.setTorchMode(cameraId, flashlightOn)
        } catch (e: Exception) {
            // Camera busy or unavailable (e.g. another app holds it) - fail
            // silently rather than crash the accessibility service.
        }
    }

    fun isFlashlightSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)

    fun takeScreenshot(service: android.accessibilityservice.AccessibilityService) {
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    /**
     * Opens the Quick Settings shade (not a specific tile - Android has no
     * public API for a third-party app to toggle an individual system tile
     * like Samsung's "Sensors Off", since that tile is OEM-internal, not
     * exposed to apps). This just gets the user one action away from it
     * instead of needing to swipe down and locate it manually - useful when
     * the screen has already gone dark and swiping down blind is awkward.
     */
    fun openQuickSettings(service: android.accessibilityservice.AccessibilityService) {
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }

    /**
     * Opens quick settings, waits [settleDelayMs] for the shade to finish
     * animating in, then dispatches a single tap at the given screen
     * coordinates. This is a best-effort automation of "open shade, tap the
     * tile the user calibrated" - it can't verify the shade actually
     * contains the right tile at that position when it fires (e.g. if a
     * notification changed the layout since calibration), so it may tap
     * the wrong thing if the position has drifted. Re-run calibration if
     * that happens.
     */
    fun openQuickSettingsAndTap(
        service: android.accessibilityservice.AccessibilityService,
        x: Int,
        y: Int,
        settleDelayMs: Long = 400L
    ) {
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            dispatchTap(service, x, y)
        }, settleDelayMs)
    }

    private fun dispatchTap(service: android.accessibilityservice.AccessibilityService, x: Int, y: Int) {
        val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        try {
            service.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            // Gesture dispatch can fail if the service loses focus or the
            // OS rejects it (e.g. over a secure/system surface) - fail
            // silently rather than crash, same policy as the rest of this
            // file's OS-facing calls.
        }
    }

    fun getBatteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun isRotationLocked(context: Context): Boolean =
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            1
        ) == 0

    fun toggleRotationLock(context: Context) {
        val locked = isRotationLocked(context)
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            if (locked) 1 else 0
        )
    }

    fun canWriteSystemSettings(context: Context): Boolean =
        Settings.System.canWrite(context)

    fun requestWriteSystemSettingsPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * Forces the screen on using a short-lived full wake lock, then lets it
     * release itself after [holdMs]. This is the actual mechanism needed to
     * turn the display on from a background service - GLOBAL_ACTION_* alone
     * is not reliable for waking an already-off screen on many OEM builds
     * (notably Samsung), since those actions are designed around an
     * already-on display.
     */
    @Suppress("DEPRECATION")
    fun wakeScreen(context: Context, holdMs: Long = 3000L) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        wakeLock?.let { if (it.isHeld) it.release() }

        wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "FrostBytePower:ShakeWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(holdMs)
        }
    }
}
