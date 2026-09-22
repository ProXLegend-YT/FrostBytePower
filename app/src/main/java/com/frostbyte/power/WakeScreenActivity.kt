package com.frostbyte.power

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * A fully transparent, momentary Activity whose only job is to force the
 * display (and lock screen, if locked) back on.
 *
 * Why this exists: FLAG_TURN_SCREEN_ON / FLAG_DISMISS_KEYGUARD on a plain
 * WindowManager overlay (e.g. from an accessibility service) worked on
 * older Android versions, but those flags were deprecated in Android 8.1
 * and, on this device's Android 10, no longer reliably wake the screen
 * when set on a service-owned window - only a real Activity is honored by
 * the platform's modern replacement APIs (setTurnScreenOn /
 * setShowWhenLocked, added in API 27). This Activity exists purely to be
 * that Activity: PowerButtonService starts it with FLAG_ACTIVITY_NEW_TASK,
 * it wakes the screen, then closes itself a moment later so nothing is
 * visibly left on screen.
 */
class WakeScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Close shortly after - just long enough for the display to
        // actually turn on and register the wake; this activity has
        // nothing to show.
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 800L)
    }
}
