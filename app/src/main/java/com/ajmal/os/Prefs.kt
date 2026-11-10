package com.ajmal.os

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object K {
    // Appearance
    const val THEME = "theme"; const val PROFILE = "profile"; const val OPACITY = "opacity"
    const val BLUR = "blur"; const val REFRACT = "refract"; const val TINT = "tint"
    const val EDGE = "edge"; const val SHADOW = "shadow"; const val ICON_APPEARANCE = "icon_appearance"
    // Icons
    const val ICON_SIZE = "icon_size"; const val ICON_RADIUS = "icon_radius"; const val LABELS = "labels"
    const val LABEL_SIZE = "label_size"; const val LABEL_COLOR = "label_color"; const val LARGE_ICONS = "large_icons"
    // Home
    const val COLS = "cols"; const val ROWS = "rows"; const val HSPACE = "hspace"; const val VSPACE = "vspace"
    const val DOTS = "dots"; const val DOCK_VISIBLE = "dock_visible"; const val DOCK_COUNT = "dock_count"
    const val DOCK_BG = "dock_bg"; const val DOCK_GLASS = "dock_glass"; const val DOCK_ICON = "dock_icon"
    const val DOCK_MARGIN = "dock_margin"; const val SEARCH_PILL = "search_pill"
    const val LOCK_LAYOUT = "lock_layout"; const val AUTO_ADD = "auto_add"
    // Animation
    const val ANIM_SPEED = "anim_speed"; const val SPRING = "spring"; const val REDUCE_MOTION = "reduce_motion"
    const val DECOR_ANIM = "decor_anim"; const val HAPTIC = "haptic"; const val ICON_ANIM = "icon_anim"
    // Gestures
    const val G_UP = "g_up"; const val G_DOWN = "g_down"; const val G_LEFT = "g_left"; const val G_RIGHT = "g_right"
    const val G_DOUBLE = "g_double"; const val G_PINCH = "g_pinch"; const val G_LONG_EMPTY = "g_long_empty"
    const val G_DOCK_UP = "g_dock_up"
    // Wallpaper / performance
    const val DIM = "dim"; const val WP_BLUR = "wp_blur"; const val REDUCED_BLUR = "reduced_blur"
    const val REDUCED_REFR = "reduced_refr"; const val FPS = "fps"
}

object Act {
    const val NONE = 0; const val NEXT_PAGE = 1; const val PREV_PAGE = 2; const val LIBRARY = 3
    const val SEARCH = 4; const val EDIT = 5; const val OVERVIEW = 6; const val LOCK = 7
    const val SETTINGS = 8; const val NOTIFS = 9; const val WIDGETS = 10
    val names = arrayOf(
        "None", "Next page", "Previous page", "App Library", "Search", "Edit mode",
        "Page overview", "Lock screen", "Launcher settings", "Notification panel", "Add widget"
    )
}

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("ajmal", Context.MODE_PRIVATE)
    private val defs = HashMap<String, Any>()

    /** Incremented on every change; HomeActivity compares it to know when to re-apply settings. */
    @Volatile var rev = 0

    init {
        d(K.THEME, 0); d(K.PROFILE, 0); d(K.OPACITY, 55); d(K.BLUR, 45); d(K.REFRACT, 35)
        d(K.TINT, 0); d(K.EDGE, 60); d(K.SHADOW, 40); d(K.ICON_APPEARANCE, 0)
        d(K.ICON_SIZE, 58); d(K.ICON_RADIUS, 23); d(K.LABELS, true); d(K.LABEL_SIZE, 11)
        d(K.LABEL_COLOR, 0); d(K.LARGE_ICONS, false)
        d(K.COLS, 4); d(K.ROWS, 6); d(K.HSPACE, 6); d(K.VSPACE, 6); d(K.DOTS, true)
        d(K.DOCK_VISIBLE, true); d(K.DOCK_COUNT, 4); d(K.DOCK_BG, true); d(K.DOCK_GLASS, 70)
        d(K.DOCK_ICON, 56); d(K.DOCK_MARGIN, 10); d(K.SEARCH_PILL, true); d(K.LOCK_LAYOUT, false)
        d(K.AUTO_ADD, true)
        d(K.ANIM_SPEED, 100); d(K.SPRING, 85); d(K.REDUCE_MOTION, false); d(K.DECOR_ANIM, true)
        d(K.HAPTIC, true); d(K.ICON_ANIM, true)
        d(K.G_UP, Act.LIBRARY); d(K.G_DOWN, Act.SEARCH); d(K.G_LEFT, Act.NEXT_PAGE)
        d(K.G_RIGHT, Act.PREV_PAGE); d(K.G_DOUBLE, Act.NONE); d(K.G_PINCH, Act.OVERVIEW)
        d(K.G_LONG_EMPTY, Act.EDIT); d(K.G_DOCK_UP, Act.NONE)
        d(K.DIM, 0); d(K.WP_BLUR, true); d(K.REDUCED_BLUR, false); d(K.REDUCED_REFR, false)
        d(K.FPS, false)
    }

    private fun d(k: String, v: Any) { defs[k] = v }

    fun int(k: String): Int = sp.getInt(k, defs[k] as Int)
    fun bool(k: String): Boolean = sp.getBoolean(k, defs[k] as Boolean)

    fun set(k: String, v: Any) {
        val e = sp.edit()
        when (v) {
            is Int -> e.putInt(k, v)
            is Boolean -> e.putBoolean(k, v)
            is String -> e.putString(k, v)
        }
        e.apply()
        rev++
    }

    fun touch() { rev++ }

    fun resetAll() {
        sp.edit().clear().apply()
        rev++
    }

    fun exportJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) o.put(k, v)
        return o
    }

    fun importJson(o: JSONObject) {
        val e = sp.edit()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val def = defs[k] ?: continue
            val v = o.get(k)
            when (def) {
                is Int -> if (v is Number) e.putInt(k, v.toInt())
                is Boolean -> if (v is Boolean) e.putBoolean(k, v)
            }
        }
        e.apply()
        rev++
    }
}
