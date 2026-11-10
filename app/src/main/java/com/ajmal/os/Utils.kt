package com.ajmal.os

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun roundBg(color: Int, radiusPx: Float): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = radiusPx
    return g
}

fun mix(a: Int, b: Int, t: Float): Int {
    val r = (Color.red(a) * (1f - t) + Color.red(b) * t).toInt()
    val g = (Color.green(a) * (1f - t) + Color.green(b) * t).toInt()
    val bl = (Color.blue(a) * (1f - t) + Color.blue(b) * t).toInt()
    return Color.rgb(r, g, bl)
}

fun label(ctx: Context, text: String, sizeSp: Float, color: Int): TextView {
    val t = TextView(ctx)
    t.text = text
    t.textSize = sizeSp
    t.setTextColor(color)
    return t
}
