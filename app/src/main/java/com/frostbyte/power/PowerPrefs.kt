package com.frostbyte.power

import android.content.Context
import android.content.SharedPreferences

enum class ButtonAction(val label: String) {
    DEFAULT("Default Action"),
    TURN_OFF_SCREEN("Turn Off Screen"),
    WAKE_SCREEN("Wake Up Screen"),
    OPEN_POWER_MENU("Open Power Menu"),
    TOGGLE_FLASHLIGHT("Toggle Flashlight"),
    TAKE_SCREENSHOT("Take Screenshot"),
    TOGGLE_PROXIMITY_OVERRIDE("Toggle Proximity Fix (Speaker on Call)"),
    OPEN_QUICK_SETTINGS("Open Quick Settings (for Sensors Off)"),
    TAP_SENSORS_OFF_TILE("Tap Sensors Off Tile (needs calibration)"),
    DISABLED("No Action (Disable Button)")
}

enum class ShakeSensitivity(val label: String, val threshold: Float) {
    DISABLED("Disabled", 0f),
    LOW("Enabled : Low Sensitive", 22f),
    MEDIUM("Enabled : Medium Sensitive", 16f),
    HIGH("Enabled : High Sensitive", 11f)
}

enum class ScreenTimeout(val label: String, val millis: Int) {
    FIFTEEN_SEC("15 seconds", 15_000),
    THIRTY_SEC("30 seconds", 30_000),
    ONE_MIN("1 minute", 60_000),
    TWO_MIN("2 minutes", 120_000),
    NEVER("Never", Int.MAX_VALUE)
}

object PowerPrefs {
    private const val PREFS_NAME = "frostbyte_power_prefs"

    private const val KEY_VOL_UP = "vol_up_action"
    private const val KEY_VOL_DOWN = "vol_down_action"
    private const val KEY_VOL_UP_DOUBLE = "vol_up_double_action"
    private const val KEY_VOL_DOWN_DOUBLE = "vol_down_double_action"
    private const val KEY_VOL_UP_LONG = "vol_up_long_action"
    private const val KEY_VOL_DOWN_LONG = "vol_down_long_action"
    private const val KEY_POWER_ACTION = "power_button_action"
    private const val KEY_SHAKE_SENSITIVITY = "shake_sensitivity"
    private const val KEY_SERVICE_ENABLED_SEEN = "onboarding_seen"
    private const val KEY_LOW_POWER_MODE = "low_power_mode"
    private const val KEY_SHAKE_BATTERY_CUTOFF_ENABLED = "shake_battery_cutoff_enabled"
    private const val KEY_SHAKE_BATTERY_CUTOFF_PERCENT = "shake_battery_cutoff_percent"
    private const val KEY_VIBRATION_FEEDBACK = "vibration_feedback"
    private const val KEY_SCREEN_TIMEOUT = "screen_timeout"
    private const val KEY_IGNORE_PROXIMITY = "ignore_proximity_sensor"
    private const val KEY_SENSORS_OFF_TAP_X = "sensors_off_tap_x"
    private const val KEY_SENSORS_OFF_TAP_Y = "sensors_off_tap_y"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getAction(context: Context, key: String): ButtonAction =
        ButtonAction.valueOf(
            prefs(context).getString(key, ButtonAction.DEFAULT.name) ?: ButtonAction.DEFAULT.name
        )

    private fun setAction(context: Context, key: String, action: ButtonAction) {
        prefs(context).edit().putString(key, action.name).apply()
    }

    fun getVolumeUpAction(context: Context) = getAction(context, KEY_VOL_UP)
    fun setVolumeUpAction(context: Context, action: ButtonAction) = setAction(context, KEY_VOL_UP, action)

    fun getVolumeDownAction(context: Context) = getAction(context, KEY_VOL_DOWN)
    fun setVolumeDownAction(context: Context, action: ButtonAction) = setAction(context, KEY_VOL_DOWN, action)

    fun getVolumeUpDoubleTapAction(context: Context) = getAction(context, KEY_VOL_UP_DOUBLE)
    fun setVolumeUpDoubleTapAction(context: Context, action: ButtonAction) = setAction(context, KEY_VOL_UP_DOUBLE, action)

    fun getVolumeDownDoubleTapAction(context: Context) = getAction(context, KEY_VOL_DOWN_DOUBLE)
    fun setVolumeDownDoubleTapAction(context: Context, action: ButtonAction) = setAction(context, KEY_VOL_DOWN_DOUBLE, action)

    fun getVolumeUpLongPressAction(context: Context) = getAction(context, KEY_VOL_UP_LONG)
    fun setVolumeUpLongPressAction(context: Context, action: ButtonAction) = setAction(context, KEY_VOL_UP_LONG, action)

    fun getVolumeDownLongPressAction(context: Context) = getAction(context, KEY_VOL_DOWN_LONG)
    fun setVolumeDownLongPressAction(context: Context, action: ButtonAction) = setAction(context, KEY_VOL_DOWN_LONG, action)

    fun getPowerButtonAction(context: Context) = getAction(context, KEY_POWER_ACTION)
    fun setPowerButtonAction(context: Context, action: ButtonAction) = setAction(context, KEY_POWER_ACTION, action)

    fun getShakeSensitivity(context: Context): ShakeSensitivity =
        ShakeSensitivity.valueOf(
            prefs(context).getString(KEY_SHAKE_SENSITIVITY, ShakeSensitivity.DISABLED.name)
                ?: ShakeSensitivity.DISABLED.name
        )

    fun setShakeSensitivity(context: Context, sensitivity: ShakeSensitivity) {
        prefs(context).edit().putString(KEY_SHAKE_SENSITIVITY, sensitivity.name).apply()
    }

    fun isLowPowerMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOW_POWER_MODE, false)

    fun setLowPowerMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOW_POWER_MODE, enabled).apply()
    }

    fun isShakeBatteryCutoffEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHAKE_BATTERY_CUTOFF_ENABLED, true)

    fun setShakeBatteryCutoffEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHAKE_BATTERY_CUTOFF_ENABLED, enabled).apply()
    }

    fun getShakeBatteryCutoffPercent(context: Context): Int =
        prefs(context).getInt(KEY_SHAKE_BATTERY_CUTOFF_PERCENT, 15)

    fun setShakeBatteryCutoffPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_SHAKE_BATTERY_CUTOFF_PERCENT, percent).apply()
    }

    fun isVibrationFeedbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBRATION_FEEDBACK, true)

    fun setVibrationFeedbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATION_FEEDBACK, enabled).apply()
    }

    fun getScreenTimeout(context: Context): ScreenTimeout =
        ScreenTimeout.valueOf(
            prefs(context).getString(KEY_SCREEN_TIMEOUT, ScreenTimeout.ONE_MIN.name) ?: ScreenTimeout.ONE_MIN.name
        )

    fun setScreenTimeout(context: Context, timeout: ScreenTimeout) {
        prefs(context).edit().putString(KEY_SCREEN_TIMEOUT, timeout.name).apply()
    }

    // Broken/misbehaving proximity sensor workaround: when enabled, calls
    // are automatically forced into speakerphone the moment they connect,
    // since the proximity sensor's screen-off behavior only ever applies
    // to earpiece mode. This is a real phone-call-only mechanism (there is
    // no "always on" variant of it, unlike the earlier wake-lock attempt).
    fun isIgnoreProximityEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IGNORE_PROXIMITY, false)

    fun setIgnoreProximityEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_IGNORE_PROXIMITY, enabled).apply()
    }

    // Saved screen coordinates for the user's "Sensors Off" quick-settings
    // tile, captured once via the on-screen calibration overlay. Android
    // gives apps no reliable way to look up a specific system tile by name
    // or ID - Samsung's quick-settings tiles aren't exposed to third-party
    // accessibility services as identifiable nodes - so instead of guessing,
    // the user marks the exact spot once and we just replay a tap there.
    // Null until calibrated. Needs re-calibrating if the tile's position
    // in the shade ever changes (reordered tiles, a notification pushing
    // things down, orientation change, etc).
    fun getSensorsOffTapPosition(context: Context): Pair<Int, Int>? {
        val x = prefs(context).getInt(KEY_SENSORS_OFF_TAP_X, -1)
        val y = prefs(context).getInt(KEY_SENSORS_OFF_TAP_Y, -1)
        return if (x >= 0 && y >= 0) x to y else null
    }

    fun setSensorsOffTapPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit()
            .putInt(KEY_SENSORS_OFF_TAP_X, x)
            .putInt(KEY_SENSORS_OFF_TAP_Y, y)
            .apply()
    }

    fun clearSensorsOffTapPosition(context: Context) {
        prefs(context).edit()
            .remove(KEY_SENSORS_OFF_TAP_X)
            .remove(KEY_SENSORS_OFF_TAP_Y)
            .apply()
    }

    fun hasSeenOnboarding(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED_SEEN, false)

    fun setSeenOnboarding(context: Context) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED_SEEN, true).apply()
    }

    // Dumps every setting to a flat map for export - deliberately excludes
    // nothing sensitive since none of these prefs are sensitive (no
    // accounts, no PII, just button mappings).
    fun exportAll(context: Context): Map<String, String> {
        val p = prefs(context)
        return p.all.mapValues { it.value?.toString() ?: "" }
    }

    fun importAll(context: Context, values: Map<String, String>) {
        val editor = prefs(context).edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }
}
