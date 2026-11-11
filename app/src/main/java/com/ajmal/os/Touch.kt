package com.ajmal.os

import android.content.Context
import android.view.ViewConfiguration
import kotlin.math.max

/**
 * Shared touch tuning. Finger jitter while waiting for a long press used to exceed the tiny system
 * touch-slop and get read as a left/right swipe (cancelling the long press). While a press is still
 * "young" the launcher therefore tolerates ~20dp of drift unless the finger is clearly flicking.
 */
object Touch {
    /** Long-press delay (system default is 500 ms, which feels slow for a launcher). */
    const val LONG_MS = 380L

    /** Drift tolerated before a press stops being a press. */
    fun hold(ctx: Context): Float =
        max(ViewConfiguration.get(ctx).scaledTouchSlop * 2.2f, 20f * ctx.resources.displayMetrics.density)

    /** Flick speed (px/s) that is treated as an intentional swipe even inside the hold window. */
    fun flick(ctx: Context): Float = 650f * ctx.resources.displayMetrics.density
}
