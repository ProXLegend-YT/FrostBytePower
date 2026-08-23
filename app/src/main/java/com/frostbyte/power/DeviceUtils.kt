package com.frostbyte.power

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings

object DeviceUtils {

    private var flashlightOn = false
    private var wakeLock: PowerManager.WakeLock? = null

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
