package com.frostbyte.power

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Detects a Volume Up/Down tap during an active call by watching the
 * voice-call stream's volume level, for devices/OS builds where the
 * system claims the raw key event for its own in-call volume UI before
 * this app's AccessibilityService.onKeyEvent ever sees it (confirmed via
 * testing: no app feedback on tap, just the plain system call-volume
 * change - see PowerButtonService.handleFallbackTap for the full history).
 *
 * Only active while a call is actually ongoing; outside a call this does
 * nothing; and the normal onKeyEvent path, which already works correctly
 * outside calls, is untouched by this class.
 */
class CallVolumeKeyFallback(
    private val context: Context,
    private val onTap: (isVolUp: Boolean) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val handler = Handler(Looper.getMainLooper())

    private var observerRegistered = false
    private var callbackRegistered = false
    private var inCall = false
    private var lastKnownVolume = -1
    private var restoreRunnable: Runnable? = null

    private val callStateCallback: TelephonyCallback? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    inCall = state == TelephonyManager.CALL_STATE_OFFHOOK
                    if (inCall) startWatching() else stopWatching()
                }
            }
        } else null

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (!inCall) return
            val current = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (lastKnownVolume == -1) {
                lastKnownVolume = current
                return
            }
            if (current == lastKnownVolume) return

            val wentUp = current > lastKnownVolume
            val atCeilingOrFloor = current == max || current == 0
            lastKnownVolume = current

            onTap(wentUp)

            // Restore the call volume to where it was so the fallback
            // action doesn't also leave the call itself louder/quieter
            // every time - unless the level was already pinned at the
            // top/bottom, where restoring would just fight the OS.
            if (!atCeilingOrFloor) {
                restoreRunnable?.let { handler.removeCallbacks(it) }
                val restoreTo = current
                val target = if (wentUp) restoreTo - 1 else restoreTo + 1
                val runnable = Runnable {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
                    lastKnownVolume = target
                }
                restoreRunnable = runnable
                handler.postDelayed(runnable, 150L)
            }
        }
    }

    fun refresh() {
        if (callbackRegistered) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            callStateCallback?.let {
                try {
                    telephonyManager.registerTelephonyCallback(context.mainExecutor, it)
                    callbackRegistered = true
                } catch (e: Exception) {
                    // Missing READ_PHONE_STATE or similar - fallback simply
                    // won't activate; the app still works outside calls.
                }
            }
        }
    }

    private fun startWatching() {
        if (observerRegistered) return
        lastKnownVolume = -1
        try {
            context.contentResolver.registerContentObserver(
                android.provider.Settings.System.CONTENT_URI,
                true,
                volumeObserver
            )
            observerRegistered = true
        } catch (e: Exception) {
            // Fail silently - the app's other features shouldn't crash
            // because of this optional fallback.
        }
    }

    private fun stopWatching() {
        if (!observerRegistered) return
        try {
            context.contentResolver.unregisterContentObserver(volumeObserver)
        } catch (e: Exception) {
            // Already unregistered or resolver gone - ignore.
        }
        observerRegistered = false
        lastKnownVolume = -1
        restoreRunnable?.let { handler.removeCallbacks(it) }
        restoreRunnable = null
    }

    fun teardown() {
        stopWatching()
        if (callbackRegistered && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            callStateCallback?.let {
                try {
                    telephonyManager.unregisterTelephonyCallback(it)
                } catch (e: Exception) {
                    // Already unregistered - ignore.
                }
            }
        }
        callbackRegistered = false
    }
}
