package com.frostbyte.power

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object AccessibilityUtils {

    fun isServiceEnabled(context: Context): Boolean {
        val expectedComponent = "${context.packageName}/${PowerButtonService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        if (TextUtils.isEmpty(enabledServices)) return false

        return enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }
}
