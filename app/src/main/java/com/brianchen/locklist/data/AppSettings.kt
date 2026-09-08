package com.brianchen.locklist.data

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.io.File

class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val themeMode: MutableState<String> = mutableStateOf(prefs.getString(KEY_THEME, THEME_SYSTEM)!!)
    val wallpaperRevision: MutableState<Long> = mutableStateOf(wallpaperFile().lastModified())
    val viewMode: MutableState<ViewMode> =
        mutableStateOf(ViewMode.fromName(prefs.getString(KEY_VIEW_MODE, null)))

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME, mode).apply()
        themeMode.value = mode
    }

    fun setViewMode(mode: ViewMode) {
        prefs.edit().putString(KEY_VIEW_MODE, mode.name).apply()
        viewMode.value = mode
    }

    fun wallpaperFile(): File = File(appContext.filesDir, WALLPAPER_NAME)

    fun markWallpaperChanged() {
        wallpaperRevision.value = wallpaperFile().lastModified()
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        private const val PREFS = "locklist_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_VIEW_MODE = "viewMode"
        private const val WALLPAPER_NAME = "wallpaper.jpg"
    }
}
