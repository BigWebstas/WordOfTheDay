package com.example.wordoftheday

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { System, Light, Dark }

/** Preferences from the Settings tab. One shared instance so the UI and the notification worker agree. */
@Stable
class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var haptics by mutableStateOf(prefs.getBoolean("haptics", true))
        private set

    var themeMode by mutableStateOf(
        prefs.getString("theme", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
    )
        private set

    /** Minutes after midnight before which the daily notification waits; -1 means as soon as a word is new. */
    var reminderMinutes by mutableIntStateOf(prefs.getInt("reminder", -1))
        private set

    /** Tree URI of the folder where backups are written on every change, or null when off. */
    var backupFolderUri by mutableStateOf(prefs.getString("backup_folder", null))
        private set

    fun updateHaptics(on: Boolean) {
        haptics = on
        prefs.edit().putBoolean("haptics", on).apply()
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun updateReminder(minutes: Int) {
        reminderMinutes = minutes
        prefs.edit().putInt("reminder", minutes).apply()
    }

    fun updateBackupFolder(uri: String?) {
        backupFolderUri = uri
        prefs.edit().putString("backup_folder", uri).apply()
    }

    companion object {
        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings = instance ?: synchronized(this) {
            instance ?: AppSettings(context.applicationContext).also { instance = it }
        }
    }
}
