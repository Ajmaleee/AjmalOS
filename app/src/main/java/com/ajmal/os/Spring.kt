package com.ajmal.os

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tiny damped-spring animator. All springs share ONE Choreographer callback that only
 * runs while at least one spring is active, so an idle launcher does zero per-frame work.
 * Honors Reduce Motion, animation speed and spring intensity (damping ratio) settings.
 */
class Spring(private val onUpdate: (Float) -> Unit) {
    var value = 0f
        private set
    var velocity = 0f
    var target = 0f
    var restDelta = 0.001f
    var isRunning = false
        internal set
    private var stiffness = 300f
    private var ratio = 0.85f
    private var onEnd: (() -> Unit)? = null

    fun snap(v: Float) {
        stop()
        value = v
        target = v
        velocity = 0f
        onUpdate(v)
    }

    fun stop() {
        if (isRunning) {
            isRunning = false
            SpringDriver.remove(this)
        }
    }

    fun to(t: Float, k: Float = 300f, v0: Float? = null, end: (() -> Unit)? = null) {
        val p = LauncherApp.get().prefs
        if (p.bool(K.REDUCE_MOTION)) {
            snap(t)
            end?.invoke()
            return
        }
        val sp = p.int(K.ANIM_SPEED) / 100f
        stiffness = k * sp * sp
        ratio = p.int(K.SPRING) / 100f
        target = t
        if (v0 != null) velocity = v0
        onEnd = end
        if (!isRunning) {
            isRunning = true
            SpringDriver.add(this)
        }
    }

    internal fun step(dt: Float): Boolean {
        val c = 2f * ratio * sqrt(stiffness)
        var left = dt
        while (left > 0f) {
            val h = min(left, 0.004f)
            velocity += (-stiffness * (value - target) - c * velocity) * h
            value += velocity * h
            left -= h
        }
        onUpdate(value)
        if (abs(value - target) < restDelta && abs(velocity) < restDelta * 10f) {
            value = target
            velocity = 0f
            onUpdate(value)
            return true
        }
        return false
    }

    internal fun finish() {
        val e = onEnd
        onEnd = null
        e?.invoke()
    }
}

object SpringDriver : Choreographer.FrameCallback {
    private val list = ArrayList<Spring>()
    private val tmp = ArrayList<Spring>()
    private var last = 0L
    private var posted = false

    fun add(s: Spring) {
        list.add(s)
        post()
    }

    fun remove(s: Spring) {
        list.remove(s)
    }

    private fun post() {
        if (!posted) {
            posted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        val dt = if (last == 0L) 0.0167f else min((frameTimeNanos - last) / 1e9f, 0.032f)
        last = frameTimeNanos
        tmp.clear()
        tmp.addAll(list)
        for (s in tmp) {
            if (!s.isRunning) continue
            if (s.step(dt)) {
                s.isRunning = false
                list.remove(s)
                s.finish()
            }
        }
        if (list.isEmpty()) last = 0L else post()
    }
}
