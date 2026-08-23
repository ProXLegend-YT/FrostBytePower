package com.frostbyte.power

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings

enum class SoundProfile(val label: String) {
    RING("Ring"),
    VIBRATE("Vibrate"),
    SILENT("Silent")
}

object QuickActions {

    fun canChangeSoundProfile(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    fun requestSoundProfilePermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun getCurrentSoundProfile(context: Context): SoundProfile {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> SoundProfile.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> SoundProfile.VIBRATE
            else -> SoundProfile.RING
        }
    }

    fun setSoundProfile(context: Context, profile: SoundProfile) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = when (profile) {
            SoundProfile.RING -> AudioManager.RINGER_MODE_NORMAL
            SoundProfile.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            SoundProfile.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
    }

    fun cycleSoundProfile(context: Context): SoundProfile {
        val next = when (getCurrentSoundProfile(context)) {
            SoundProfile.RING -> SoundProfile.VIBRATE
            SoundProfile.VIBRATE -> SoundProfile.SILENT
            SoundProfile.SILENT -> SoundProfile.RING
        }
        setSoundProfile(context, next)
        return next
    }

    fun lockScreen() {
        PowerButtonService.instance?.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        )
    }

    fun openPowerMenu() {
        PowerButtonService.instance?.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        )
    }

    fun toggleFlashlight(context: Context) {
        DeviceUtils.toggleFlashlight(context)
    }

    fun toggleRotationLock(context: Context) {
        DeviceUtils.toggleRotationLock(context)
    }

    fun toggleDnd(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            requestSoundProfilePermission(context)
            return
        }
        val isDndOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(
            if (isDndOn) NotificationManager.INTERRUPTION_FILTER_ALL
            else NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )
    }

    fun isDndOn(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }
}
