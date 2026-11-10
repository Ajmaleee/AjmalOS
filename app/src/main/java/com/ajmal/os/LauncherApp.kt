package com.ajmal.os

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Color

class LauncherApp : Application() {
    lateinit var prefs: Prefs
    lateinit var model: LauncherModel
    lateinit var icons: IconCache
    lateinit var glass: GlassRenderer

    override fun onCreate() {
        super.onCreate()
        inst = this
        prefs = Prefs(this)
        icons = IconCache(this)
        glass = GlassRenderer(this)
        model = LauncherModel(this)
        model.load()
        glass.reload()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) icons.trim()
    }

    fun isDark(): Boolean = when (prefs.int(K.THEME)) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun tintColor(): Int = when (prefs.int(K.TINT)) {
        1 -> 0xFF4F7DF3.toInt()
        2 -> 0xFFE59A4A.toInt()
        3 -> 0xFF3FB27F.toInt()
        4 -> 0xFFE0629A.toInt()
        5 -> glass.avgColor
        else -> 0xFF8E8E93.toInt()
    }

    fun glassBase(dark: Boolean): Int {
        if (prefs.int(K.TINT) == 0) return if (dark) 0xFF16171B.toInt() else Color.WHITE
        val c = tintColor()
        return if (dark) mix(c, Color.BLACK, 0.55f) else mix(c, Color.WHITE, 0.6f)
    }

    companion object {
        private lateinit var inst: LauncherApp
        fun get(): LauncherApp = inst
    }
}
