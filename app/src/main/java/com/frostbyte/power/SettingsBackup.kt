package com.frostbyte.power

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Exports/imports app settings as a small local JSON file. No network
 * involved - the file is written/read via a Storage Access Framework Uri
 * that MainActivity obtains through the system file picker.
 */
object SettingsBackup {

    fun exportToJson(context: Context): String {
        val map = PowerPrefs.exportAll(context)
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value) }
        return json.toString(2)
    }

    fun writeToUri(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(exportToJson(context).toByteArray())
        }
    }

    fun readFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            } ?: return false

            val json = JSONObject(text)
            val values = mutableMapOf<String, String>()
            json.keys().forEach { key -> values[key] = json.getString(key) }
            PowerPrefs.importAll(context, values)
            true
        } catch (e: Exception) {
            false
        }
    }
}
