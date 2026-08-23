package com.frostbyte.power

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Workaround for a broken/stuck proximity sensor causing a black,
 * unresponsive screen during calls. Confirmed with Samsung support: the
 * proximity-controlled screen-off during calls is an OS-level behavior on
 * One UI with no user-facing or developer API to disable it directly - a
 * wake lock and a forced-on activity were both tried and both failed to
 * override it (see git history).
 *
 * The actual fix: the proximity sensor only controls the screen in
 * EARPIECE audio mode, because that's the only mode where holding the
 * phone to your face makes sense. SPEAKERPHONE mode never triggers it,
 * by design, on every Android device including Samsung's. So instead of
 * fighting the sensor, this forces the call into speakerphone the moment
 * it connects, which sidesteps the proximity logic entirely rather than
 * trying to override it.
 *
 * Known gap: this only applies to actual phone calls and WhatsApp voice
 * calls, both of which route through the standard telephony/audio stack.
 * It does not reliably affect WhatsApp voice MESSAGE playback (holding the
 * phone to your ear to listen to a note), since WhatsApp manages that
 * audio route internally rather than through a system call state.
 */
class ProximityOverride(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var callbackRegistered = false
    private var forcedSpeakerThisCall = false

    private val callStateCallback: TelephonyCallback? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state)
                }
            }
        } else null

    @Suppress("DEPRECATION")
    private val legacyPhoneStateListener: android.telephony.PhoneStateListener? =
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            object : android.telephony.PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state)
                }
            }
        } else null

    /** Applies current prefs: registers/unregisters the call-state listener. */
    fun refresh() {
        if (PowerPrefs.isIgnoreProximityEnabled(context)) {
            registerCallListener()
        } else {
            unregisterCallListener()
        }
    }

    fun teardown() {
        unregisterCallListener()
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> forceSpeakerphone()
            TelephonyManager.CALL_STATE_IDLE -> forcedSpeakerThisCall = false
            TelephonyManager.CALL_STATE_RINGING -> Unit
        }
    }

    private fun forceSpeakerphone() {
        if (forcedSpeakerThisCall) return
        try {
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true
            forcedSpeakerThisCall = true
        } catch (e: Exception) {
            // If audio routing can't be changed for any reason, fail
            // silently rather than crash the accessibility service - the
            // call itself is unaffected either way.
        }
    }

    @Suppress("DEPRECATION")
    private fun registerCallListener() {
        if (callbackRegistered) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                callStateCallback?.let {
                    telephonyManager.registerTelephonyCallback(context.mainExecutor, it)
                }
            } else {
                legacyPhoneStateListener?.let {
                    telephonyManager.listen(it, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
                }
            }
            callbackRegistered = true
        } catch (e: SecurityException) {
            // READ_PHONE_STATE not granted - the feature simply won't
            // auto-trigger until the permission is granted.
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterCallListener() {
        if (!callbackRegistered) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            callStateCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        } else {
            legacyPhoneStateListener?.let {
                telephonyManager.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE)
            }
        }
        callbackRegistered = false
    }
}
