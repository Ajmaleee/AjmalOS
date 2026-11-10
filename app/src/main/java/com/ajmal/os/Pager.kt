package com.ajmal.os

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

interface PagerCallbacks {
    fun onPageSettled(index: Int)
    fun onScroll(pos: Float)
    fun onAction(a: Int)
    fun onLongPressEmpty(x: Float, y: Float)
    fun onPinchIn()
}

/**
 * Horizontal pager with immediate touch tracking, spring snapping and the configurable gesture
 * engine (swipe up/down/left/right, double tap, long press, pinch). Touch tracking never waits
 * for an animation: a running snap is caught by the next touch.
 */
class HomePager(ctx: Context, private val cb: PagerCallbacks) : ViewGroup(ctx) {
    val pages = ArrayList<CellLayout>()
    var index = 0
        private set
    var editing = false

    private val prefs = LauncherApp.get().prefs
    private val dp = ctx.resources.displayMetrics.density
    private var pos = 0f
    private val spring = Spring { v -> setPos(v) }
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val maxFling = ViewConfiguration.get(ctx).scaledMaximumFlingVelocity.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var axis = 0
    private var tracker: VelocityTracker? = null
    private var onWidget = false
    private var pinched = false
    private var acc = 1f
    private var paging = true

    private val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { acc = 1f; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            acc *= d.scaleFactor
            if (!pinched && acc < 0.82f) {
                pinched = true
                cb.onPinchIn()
            }
            return true
        }
    })

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (axis == 0 && !pinched) cb.onLongPressEmpty(e.rawX, e.rawY)
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            cb.onAction(prefs.int(K.G_DOUBLE))
            return true
        }
    })

    init { clipChildren = false }

    fun setPages(list: List<CellLayout>, keepIndex: Int) {
        removeAllViews()
        pages.clear()
        for (p in list) { pages.add(p); addView(p) }
        index = keepIndex.coerceIn(0, max(0, pages.size - 1))
        spring.snap(index * width.toFloat())
    }

    fun currentPage(): CellLayout? = pages.getOrNull(index)

    fun jumpTo(i: Int) {
        index = i.coerceIn(0, max(0, pages.size - 1))
        spring.snap(index * width.toFloat())
        cb.onPageSettled(index)
    }

    fun goTo(i: Int, v0: Float = 0f) {
        val t = i.coerceIn(0, max(0, pages.size - 1))
        index = t
        val w = width.toFloat()
        spring.restDelta = 0.5f
        spring.to(t * w, 230f, -v0) { cb.onPageSettled(t) }
    }

    private fun setPos(v: Float) {
        pos = v
        for (i in 0 until childCount) getChildAt(i).translationX = -v
        cb.onScroll(if (width > 0) v / width else 0f)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, i * w + w, b - t)
        if (changed && !spring.isRunning) setPos(index * w.toFloat())
    }

    private fun hitWidget(x: Float, y: Float): Boolean {
        val page = currentPage() ?: return false
        if (abs(pos - index * width) > 2f) return false
        for (i in 0 until page.childCount) {
            val v = page.getChildAt(i)
            val m = v.tag as? Item ?: continue
            if (m.type == Item.WIDGET && x >= v.left && x <= v.right && y >= v.top && y <= v.bottom) return true
        }
        return false
    }

    private fun detectAxis(e: MotionEvent): Boolean {
        if (axis != 0) return axis != 0
        val dx = e.x - downX
        val dy = e.y - downY
        if (abs(dx) > slop && abs(dx) > abs(dy)) { axis = 1; lastX = e.x; return true }
        if (abs(dy) > slop && abs(dy) > abs(dx) && !onWidget) { axis = 2; return true }
        return false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        if (e.pointerCount > 1) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; axis = 0; pinched = false
                paging = prefs.int(K.G_LEFT) == Act.NEXT_PAGE && prefs.int(K.G_RIGHT) == Act.PREV_PAGE
                onWidget = hitWidget(e.x, e.y)
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker!!.addMovement(e)
                if (spring.isRunning) { axis = 1; spring.stop(); return true }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (axis == 0 && detectAxis(e)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        gd.onTouchEvent(e)
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1 || pinched) return true
                detectAxis(e)
                if (axis == 1 && paging) {
                    val dx = lastX - e.x
                    lastX = e.x
                    val max = (pages.size - 1) * width.toFloat()
                    val np = if ((pos < 0f && dx < 0f) || (pos > max && dx > 0f)) pos + dx * 0.35f else pos + dx
                    setPos(np)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish(e)
        }
        return true
    }

    private fun finish(e: MotionEvent) {
        val t = tracker
        var vx = 0f
        var vy = 0f
        if (t != null) {
            t.computeCurrentVelocity(1000, maxFling)
            vx = t.xVelocity
            vy = t.yVelocity
            t.recycle()
            tracker = null
        }
        val a = axis
        axis = 0
        if (e.actionMasked == MotionEvent.ACTION_CANCEL || pinched) {
            if (a == 1 && paging) settle(0f)
            return
        }
        val dx = e.x - downX
        val dy = e.y - downY
        if (a == 1) {
            if (paging) settle(vx)
            else if (abs(vx) > 900f || abs(dx) > 90f * dp) cb.onAction(prefs.int(if (dx < 0) K.G_LEFT else K.G_RIGHT))
        } else if (a == 2) {
            if (abs(dy) > 70f * dp || abs(vy) > 1200f) cb.onAction(prefs.int(if (dy < 0) K.G_UP else K.G_DOWN))
        }
    }

    private fun settle(vx: Float) {
        val w = width.toFloat()
        if (w <= 0f) return
        val cur = pos / w
        var target = cur.roundToInt()
        if (vx < -600f) target = floor(cur).toInt() + 1
        else if (vx > 600f) target = ceil(cur).toInt() - 1
        goTo(target, vx)
    }
}
